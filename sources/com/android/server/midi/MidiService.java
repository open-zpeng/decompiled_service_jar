package com.android.server.midi;

import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.XmlResourceParser;
import android.media.midi.IBluetoothMidiService;
import android.media.midi.IMidiDeviceListener;
import android.media.midi.IMidiDeviceOpenCallback;
import android.media.midi.IMidiDeviceServer;
import android.media.midi.IMidiManager;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiDeviceStatus;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import com.android.internal.content.PackageMonitor;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.SystemService;
import com.android.server.pm.Settings;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
/* loaded from: classes.dex */
public class MidiService extends IMidiManager.Stub {
    private static final MidiDeviceInfo[] EMPTY_DEVICE_INFO_ARRAY = new MidiDeviceInfo[0];
    private static final String[] EMPTY_STRING_ARRAY = new String[0];
    private static final String TAG = "MidiService";
    private final Context mContext;
    private final PackageManager mPackageManager;
    private final HashMap<IBinder, Client> mClients = new HashMap<>();
    private final HashMap<MidiDeviceInfo, Device> mDevicesByInfo = new HashMap<>();
    private final HashMap<BluetoothDevice, Device> mBluetoothDevices = new HashMap<>();
    private final HashMap<IBinder, Device> mDevicesByServer = new HashMap<>();
    private int mNextDeviceId = 1;
    private final PackageMonitor mPackageMonitor = new PackageMonitor() { // from class: com.android.server.midi.MidiService.1
        public void onPackageAdded(String packageName, int uid) {
            MidiService.this.addPackageDeviceServers(packageName);
        }

        public void onPackageModified(String packageName) {
            MidiService.this.removePackageDeviceServers(packageName);
            MidiService.this.addPackageDeviceServers(packageName);
        }

        public void onPackageRemoved(String packageName, int uid) {
            MidiService.this.removePackageDeviceServers(packageName);
        }
    };
    private int mBluetoothServiceUid = -1;

    /* loaded from: classes.dex */
    public static class Lifecycle extends SystemService {
        private MidiService mMidiService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override // com.android.server.SystemService
        public void onStart() {
            this.mMidiService = new MidiService(getContext());
            publishBinderService("midi", this.mMidiService);
        }

        @Override // com.android.server.SystemService
        public void onUnlockUser(int userHandle) {
            if (userHandle == 0) {
                this.mMidiService.onUnlockUser();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class Client implements IBinder.DeathRecipient {
        private final IBinder mToken;
        private final HashMap<IBinder, IMidiDeviceListener> mListeners = new HashMap<>();
        private final HashMap<IBinder, DeviceConnection> mDeviceConnections = new HashMap<>();
        private final int mUid = Binder.getCallingUid();
        private final int mPid = Binder.getCallingPid();

        public Client(IBinder token) {
            this.mToken = token;
        }

        public int getUid() {
            return this.mUid;
        }

        public void addListener(IMidiDeviceListener listener) {
            this.mListeners.put(listener.asBinder(), listener);
        }

        public void removeListener(IMidiDeviceListener listener) {
            this.mListeners.remove(listener.asBinder());
            if (this.mListeners.size() == 0 && this.mDeviceConnections.size() == 0) {
                close();
            }
        }

        public void addDeviceConnection(Device device, IMidiDeviceOpenCallback callback) {
            DeviceConnection connection = new DeviceConnection(device, this, callback);
            this.mDeviceConnections.put(connection.getToken(), connection);
            device.addDeviceConnection(connection);
        }

        public void removeDeviceConnection(IBinder token) {
            DeviceConnection connection = this.mDeviceConnections.remove(token);
            if (connection != null) {
                connection.getDevice().removeDeviceConnection(connection);
            }
            if (this.mListeners.size() == 0 && this.mDeviceConnections.size() == 0) {
                close();
            }
        }

        public void removeDeviceConnection(DeviceConnection connection) {
            this.mDeviceConnections.remove(connection.getToken());
            if (this.mListeners.size() == 0 && this.mDeviceConnections.size() == 0) {
                close();
            }
        }

        public void deviceAdded(Device device) {
            if (device.isUidAllowed(this.mUid)) {
                MidiDeviceInfo deviceInfo = device.getDeviceInfo();
                try {
                    for (IMidiDeviceListener listener : this.mListeners.values()) {
                        listener.onDeviceAdded(deviceInfo);
                    }
                } catch (RemoteException e) {
                    Log.e(MidiService.TAG, "remote exception", e);
                }
            }
        }

        public void deviceRemoved(Device device) {
            if (device.isUidAllowed(this.mUid)) {
                MidiDeviceInfo deviceInfo = device.getDeviceInfo();
                try {
                    for (IMidiDeviceListener listener : this.mListeners.values()) {
                        listener.onDeviceRemoved(deviceInfo);
                    }
                } catch (RemoteException e) {
                    Log.e(MidiService.TAG, "remote exception", e);
                }
            }
        }

        public void deviceStatusChanged(Device device, MidiDeviceStatus status) {
            if (device.isUidAllowed(this.mUid)) {
                try {
                    for (IMidiDeviceListener listener : this.mListeners.values()) {
                        listener.onDeviceStatusChanged(status);
                    }
                } catch (RemoteException e) {
                    Log.e(MidiService.TAG, "remote exception", e);
                }
            }
        }

        private void close() {
            synchronized (MidiService.this.mClients) {
                MidiService.this.mClients.remove(this.mToken);
                this.mToken.unlinkToDeath(this, 0);
            }
            for (DeviceConnection connection : this.mDeviceConnections.values()) {
                connection.getDevice().removeDeviceConnection(connection);
            }
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            Log.d(MidiService.TAG, "Client died: " + this);
            close();
        }

        public String toString() {
            StringBuilder sb = new StringBuilder("Client: UID: ");
            sb.append(this.mUid);
            sb.append(" PID: ");
            sb.append(this.mPid);
            sb.append(" listener count: ");
            sb.append(this.mListeners.size());
            sb.append(" Device Connections:");
            for (DeviceConnection connection : this.mDeviceConnections.values()) {
                sb.append(" <device ");
                sb.append(connection.getDevice().getDeviceInfo().getId());
                sb.append(">");
            }
            return sb.toString();
        }
    }

    private Client getClient(IBinder token) {
        Client client;
        synchronized (this.mClients) {
            client = this.mClients.get(token);
            if (client == null) {
                client = new Client(token);
                try {
                    token.linkToDeath(client, 0);
                    this.mClients.put(token, client);
                } catch (RemoteException e) {
                    return null;
                }
            }
        }
        return client;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class Device implements IBinder.DeathRecipient {
        private final BluetoothDevice mBluetoothDevice;
        private final ArrayList<DeviceConnection> mDeviceConnections;
        private MidiDeviceInfo mDeviceInfo;
        private MidiDeviceStatus mDeviceStatus;
        private IMidiDeviceServer mServer;
        private ServiceConnection mServiceConnection;
        private final ServiceInfo mServiceInfo;
        private final int mUid;

        public Device(IMidiDeviceServer server, MidiDeviceInfo deviceInfo, ServiceInfo serviceInfo, int uid) {
            this.mDeviceConnections = new ArrayList<>();
            this.mDeviceInfo = deviceInfo;
            this.mServiceInfo = serviceInfo;
            this.mUid = uid;
            this.mBluetoothDevice = (BluetoothDevice) deviceInfo.getProperties().getParcelable("bluetooth_device");
            setDeviceServer(server);
        }

        public Device(BluetoothDevice bluetoothDevice) {
            this.mDeviceConnections = new ArrayList<>();
            this.mBluetoothDevice = bluetoothDevice;
            this.mServiceInfo = null;
            this.mUid = MidiService.this.mBluetoothServiceUid;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void setDeviceServer(IMidiDeviceServer server) {
            if (server != null) {
                if (this.mServer != null) {
                    Log.e(MidiService.TAG, "mServer already set in setDeviceServer");
                    return;
                }
                IBinder binder = server.asBinder();
                try {
                    binder.linkToDeath(this, 0);
                    this.mServer = server;
                    MidiService.this.mDevicesByServer.put(binder, this);
                } catch (RemoteException e) {
                    this.mServer = null;
                    return;
                }
            } else if (this.mServer != null) {
                server = this.mServer;
                this.mServer = null;
                IBinder binder2 = server.asBinder();
                MidiService.this.mDevicesByServer.remove(binder2);
                try {
                    server.closeDevice();
                    binder2.unlinkToDeath(this, 0);
                } catch (RemoteException e2) {
                }
            }
            if (this.mDeviceConnections != null) {
                Iterator<DeviceConnection> it = this.mDeviceConnections.iterator();
                while (it.hasNext()) {
                    DeviceConnection connection = it.next();
                    connection.notifyClient(server);
                }
            }
        }

        public MidiDeviceInfo getDeviceInfo() {
            return this.mDeviceInfo;
        }

        public void setDeviceInfo(MidiDeviceInfo deviceInfo) {
            this.mDeviceInfo = deviceInfo;
        }

        public MidiDeviceStatus getDeviceStatus() {
            return this.mDeviceStatus;
        }

        public void setDeviceStatus(MidiDeviceStatus status) {
            this.mDeviceStatus = status;
        }

        public IMidiDeviceServer getDeviceServer() {
            return this.mServer;
        }

        public ServiceInfo getServiceInfo() {
            return this.mServiceInfo;
        }

        public String getPackageName() {
            if (this.mServiceInfo == null) {
                return null;
            }
            return this.mServiceInfo.packageName;
        }

        public int getUid() {
            return this.mUid;
        }

        public boolean isUidAllowed(int uid) {
            return !this.mDeviceInfo.isPrivate() || this.mUid == uid;
        }

        public void addDeviceConnection(DeviceConnection connection) {
            Intent intent;
            synchronized (this.mDeviceConnections) {
                if (this.mServer != null) {
                    this.mDeviceConnections.add(connection);
                    connection.notifyClient(this.mServer);
                } else if (this.mServiceConnection == null && (this.mServiceInfo != null || this.mBluetoothDevice != null)) {
                    this.mDeviceConnections.add(connection);
                    this.mServiceConnection = new ServiceConnection() { // from class: com.android.server.midi.MidiService.Device.1
                        @Override // android.content.ServiceConnection
                        public void onServiceConnected(ComponentName name, IBinder service) {
                            IMidiDeviceServer server = null;
                            if (Device.this.mBluetoothDevice != null) {
                                IBluetoothMidiService mBluetoothMidiService = IBluetoothMidiService.Stub.asInterface(service);
                                try {
                                    IBinder deviceBinder = mBluetoothMidiService.addBluetoothDevice(Device.this.mBluetoothDevice);
                                    server = IMidiDeviceServer.Stub.asInterface(deviceBinder);
                                } catch (RemoteException e) {
                                    Log.e(MidiService.TAG, "Could not call addBluetoothDevice()", e);
                                }
                            } else {
                                server = IMidiDeviceServer.Stub.asInterface(service);
                            }
                            Device.this.setDeviceServer(server);
                        }

                        @Override // android.content.ServiceConnection
                        public void onServiceDisconnected(ComponentName name) {
                            Device.this.setDeviceServer(null);
                            Device.this.mServiceConnection = null;
                        }
                    };
                    if (this.mBluetoothDevice != null) {
                        intent = new Intent("android.media.midi.BluetoothMidiService");
                        intent.setComponent(new ComponentName("com.android.bluetoothmidiservice", "com.android.bluetoothmidiservice.BluetoothMidiService"));
                    } else {
                        intent = new Intent("android.media.midi.MidiDeviceService");
                        intent.setComponent(new ComponentName(this.mServiceInfo.packageName, this.mServiceInfo.name));
                    }
                    if (!MidiService.this.mContext.bindService(intent, this.mServiceConnection, 1)) {
                        Log.e(MidiService.TAG, "Unable to bind service: " + intent);
                        setDeviceServer(null);
                        this.mServiceConnection = null;
                    }
                } else {
                    Log.e(MidiService.TAG, "No way to connect to device in addDeviceConnection");
                    connection.notifyClient(null);
                }
            }
        }

        public void removeDeviceConnection(DeviceConnection connection) {
            synchronized (this.mDeviceConnections) {
                this.mDeviceConnections.remove(connection);
                if (this.mDeviceConnections.size() == 0 && this.mServiceConnection != null) {
                    MidiService.this.mContext.unbindService(this.mServiceConnection);
                    this.mServiceConnection = null;
                    if (this.mBluetoothDevice != null) {
                        synchronized (MidiService.this.mDevicesByInfo) {
                            closeLocked();
                        }
                    } else {
                        setDeviceServer(null);
                    }
                }
            }
        }

        public void closeLocked() {
            synchronized (this.mDeviceConnections) {
                Iterator<DeviceConnection> it = this.mDeviceConnections.iterator();
                while (it.hasNext()) {
                    DeviceConnection connection = it.next();
                    connection.getClient().removeDeviceConnection(connection);
                }
                this.mDeviceConnections.clear();
            }
            setDeviceServer(null);
            if (this.mServiceInfo == null) {
                MidiService.this.removeDeviceLocked(this);
            } else {
                this.mDeviceStatus = new MidiDeviceStatus(this.mDeviceInfo);
            }
            if (this.mBluetoothDevice != null) {
                MidiService.this.mBluetoothDevices.remove(this.mBluetoothDevice);
            }
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            Log.d(MidiService.TAG, "Device died: " + this);
            synchronized (MidiService.this.mDevicesByInfo) {
                closeLocked();
            }
        }

        public String toString() {
            return "Device Info: " + this.mDeviceInfo + " Status: " + this.mDeviceStatus + " UID: " + this.mUid + " DeviceConnection count: " + this.mDeviceConnections.size() + " mServiceConnection: " + this.mServiceConnection;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class DeviceConnection {
        private IMidiDeviceOpenCallback mCallback;
        private final Client mClient;
        private final Device mDevice;
        private final IBinder mToken = new Binder();

        public DeviceConnection(Device device, Client client, IMidiDeviceOpenCallback callback) {
            this.mDevice = device;
            this.mClient = client;
            this.mCallback = callback;
        }

        public Device getDevice() {
            return this.mDevice;
        }

        public Client getClient() {
            return this.mClient;
        }

        public IBinder getToken() {
            return this.mToken;
        }

        public void notifyClient(IMidiDeviceServer deviceServer) {
            if (this.mCallback != null) {
                try {
                    this.mCallback.onDeviceOpened(deviceServer, deviceServer == null ? null : this.mToken);
                } catch (RemoteException e) {
                }
                this.mCallback = null;
            }
        }

        public String toString() {
            return "DeviceConnection Device ID: " + this.mDevice.getDeviceInfo().getId();
        }
    }

    public MidiService(Context context) {
        this.mContext = context;
        this.mPackageManager = context.getPackageManager();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onUnlockUser() {
        PackageInfo info = null;
        this.mPackageMonitor.register(this.mContext, (Looper) null, true);
        Intent intent = new Intent("android.media.midi.MidiDeviceService");
        List<ResolveInfo> resolveInfos = this.mPackageManager.queryIntentServices(intent, 128);
        if (resolveInfos != null) {
            int count = resolveInfos.size();
            for (int i = 0; i < count; i++) {
                ServiceInfo serviceInfo = resolveInfos.get(i).serviceInfo;
                if (serviceInfo != null) {
                    addPackageDeviceServer(serviceInfo);
                }
            }
        }
        try {
            info = this.mPackageManager.getPackageInfo("com.android.bluetoothmidiservice", 0);
        } catch (PackageManager.NameNotFoundException e) {
        }
        PackageInfo info2 = info;
        if (info2 != null && info2.applicationInfo != null) {
            this.mBluetoothServiceUid = info2.applicationInfo.uid;
        } else {
            this.mBluetoothServiceUid = -1;
        }
    }

    public void registerListener(IBinder token, IMidiDeviceListener listener) {
        Client client = getClient(token);
        if (client == null) {
            return;
        }
        client.addListener(listener);
        updateStickyDeviceStatus(client.mUid, listener);
    }

    public void unregisterListener(IBinder token, IMidiDeviceListener listener) {
        Client client = getClient(token);
        if (client == null) {
            return;
        }
        client.removeListener(listener);
    }

    private void updateStickyDeviceStatus(int uid, IMidiDeviceListener listener) {
        synchronized (this.mDevicesByInfo) {
            for (Device device : this.mDevicesByInfo.values()) {
                if (device.isUidAllowed(uid)) {
                    try {
                        MidiDeviceStatus status = device.getDeviceStatus();
                        if (status != null) {
                            listener.onDeviceStatusChanged(status);
                        }
                    } catch (RemoteException e) {
                        Log.e(TAG, "remote exception", e);
                    }
                }
            }
        }
    }

    public MidiDeviceInfo[] getDevices() {
        ArrayList<MidiDeviceInfo> deviceInfos = new ArrayList<>();
        int uid = Binder.getCallingUid();
        synchronized (this.mDevicesByInfo) {
            for (Device device : this.mDevicesByInfo.values()) {
                if (device.isUidAllowed(uid)) {
                    deviceInfos.add(device.getDeviceInfo());
                }
            }
        }
        return (MidiDeviceInfo[]) deviceInfos.toArray(EMPTY_DEVICE_INFO_ARRAY);
    }

    public void openDevice(IBinder token, MidiDeviceInfo deviceInfo, IMidiDeviceOpenCallback callback) {
        Device device;
        Client client = getClient(token);
        if (client == null) {
            return;
        }
        synchronized (this.mDevicesByInfo) {
            device = this.mDevicesByInfo.get(deviceInfo);
            if (device == null) {
                throw new IllegalArgumentException("device does not exist: " + deviceInfo);
            } else if (!device.isUidAllowed(Binder.getCallingUid())) {
                throw new SecurityException("Attempt to open private device with wrong UID");
            }
        }
        long identity = Binder.clearCallingIdentity();
        try {
            client.addDeviceConnection(device, callback);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void openBluetoothDevice(IBinder token, BluetoothDevice bluetoothDevice, IMidiDeviceOpenCallback callback) {
        Device device;
        Client client = getClient(token);
        if (client == null) {
            return;
        }
        synchronized (this.mDevicesByInfo) {
            device = this.mBluetoothDevices.get(bluetoothDevice);
            if (device == null) {
                device = new Device(bluetoothDevice);
                this.mBluetoothDevices.put(bluetoothDevice, device);
            }
        }
        Device device2 = device;
        long identity = Binder.clearCallingIdentity();
        try {
            client.addDeviceConnection(device2, callback);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void closeDevice(IBinder clientToken, IBinder deviceToken) {
        Client client = getClient(clientToken);
        if (client == null) {
            return;
        }
        client.removeDeviceConnection(deviceToken);
    }

    public MidiDeviceInfo registerDeviceServer(IMidiDeviceServer server, int numInputPorts, int numOutputPorts, String[] inputPortNames, String[] outputPortNames, Bundle properties, int type) {
        MidiDeviceInfo addDeviceLocked;
        int uid = Binder.getCallingUid();
        if (type != 1 || uid == 1000) {
            if (type == 3 && uid != this.mBluetoothServiceUid) {
                throw new SecurityException("only MidiBluetoothService can create Bluetooth devices");
            }
            synchronized (this.mDevicesByInfo) {
                addDeviceLocked = addDeviceLocked(type, numInputPorts, numOutputPorts, inputPortNames, outputPortNames, properties, server, null, false, uid);
            }
            return addDeviceLocked;
        }
        throw new SecurityException("only system can create USB devices");
    }

    public void unregisterDeviceServer(IMidiDeviceServer server) {
        synchronized (this.mDevicesByInfo) {
            Device device = this.mDevicesByServer.get(server.asBinder());
            if (device != null) {
                device.closeLocked();
            }
        }
    }

    public MidiDeviceInfo getServiceDeviceInfo(String packageName, String className) {
        synchronized (this.mDevicesByInfo) {
            for (Device device : this.mDevicesByInfo.values()) {
                ServiceInfo serviceInfo = device.getServiceInfo();
                if (serviceInfo != null && packageName.equals(serviceInfo.packageName) && className.equals(serviceInfo.name)) {
                    return device.getDeviceInfo();
                }
            }
            return null;
        }
    }

    public MidiDeviceStatus getDeviceStatus(MidiDeviceInfo deviceInfo) {
        Device device = this.mDevicesByInfo.get(deviceInfo);
        if (device == null) {
            throw new IllegalArgumentException("no such device for " + deviceInfo);
        }
        return device.getDeviceStatus();
    }

    public void setDeviceStatus(IMidiDeviceServer server, MidiDeviceStatus status) {
        Device device = this.mDevicesByServer.get(server.asBinder());
        if (device != null) {
            if (Binder.getCallingUid() != device.getUid()) {
                throw new SecurityException("setDeviceStatus() caller UID " + Binder.getCallingUid() + " does not match device's UID " + device.getUid());
            }
            device.setDeviceStatus(status);
            notifyDeviceStatusChanged(device, status);
        }
    }

    private void notifyDeviceStatusChanged(Device device, MidiDeviceStatus status) {
        synchronized (this.mClients) {
            for (Client c : this.mClients.values()) {
                c.deviceStatusChanged(device, status);
            }
        }
    }

    private MidiDeviceInfo addDeviceLocked(int type, int numInputPorts, int numOutputPorts, String[] inputPortNames, String[] outputPortNames, Bundle properties, IMidiDeviceServer server, ServiceInfo serviceInfo, boolean isPrivate, int uid) {
        int id = this.mNextDeviceId;
        this.mNextDeviceId = id + 1;
        MidiDeviceInfo deviceInfo = new MidiDeviceInfo(type, id, numInputPorts, numOutputPorts, inputPortNames, outputPortNames, properties, isPrivate);
        if (server != null) {
            try {
                server.setDeviceInfo(deviceInfo);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException in setDeviceInfo()");
                return null;
            }
        }
        Device device = null;
        BluetoothDevice bluetoothDevice = null;
        if (type == 3) {
            bluetoothDevice = (BluetoothDevice) properties.getParcelable("bluetooth_device");
            Device device2 = this.mBluetoothDevices.get(bluetoothDevice);
            device = device2;
            if (device != null) {
                device.setDeviceInfo(deviceInfo);
            }
        }
        BluetoothDevice bluetoothDevice2 = bluetoothDevice;
        if (device == null) {
            device = new Device(server, deviceInfo, serviceInfo, uid);
        }
        Device device3 = device;
        this.mDevicesByInfo.put(deviceInfo, device3);
        if (bluetoothDevice2 != null) {
            this.mBluetoothDevices.put(bluetoothDevice2, device3);
        }
        synchronized (this.mClients) {
            for (Client c : this.mClients.values()) {
                c.deviceAdded(device3);
            }
        }
        return deviceInfo;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void removeDeviceLocked(Device device) {
        IMidiDeviceServer server = device.getDeviceServer();
        if (server != null) {
            this.mDevicesByServer.remove(server.asBinder());
        }
        this.mDevicesByInfo.remove(device.getDeviceInfo());
        synchronized (this.mClients) {
            for (Client c : this.mClients.values()) {
                c.deviceRemoved(device);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void addPackageDeviceServers(String packageName) {
        try {
            PackageInfo info = this.mPackageManager.getPackageInfo(packageName, 132);
            ServiceInfo[] services = info.services;
            if (services == null) {
                return;
            }
            for (ServiceInfo serviceInfo : services) {
                addPackageDeviceServer(serviceInfo);
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "handlePackageUpdate could not find package " + packageName, e);
        }
    }

    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:114:? -> B:84:0x01ef). Please submit an issue!!! */
    private void addPackageDeviceServer(ServiceInfo serviceInfo) {
        XmlResourceParser parser;
        ArrayList<String> outputPortNames;
        ArrayList<String> outputPortNames2;
        HashMap<MidiDeviceInfo, Device> hashMap;
        MidiService midiService;
        MidiService midiService2 = this;
        XmlResourceParser parser2 = null;
        try {
            try {
                parser = serviceInfo.loadXmlMetaData(midiService2.mPackageManager, "android.media.midi.MidiDeviceService");
                if (parser == null) {
                    if (parser != null) {
                        parser.close();
                        return;
                    }
                    return;
                }
                try {
                    if (!"android.permission.BIND_MIDI_DEVICE_SERVICE".equals(serviceInfo.permission)) {
                        Log.w(TAG, "Skipping MIDI device service " + serviceInfo.packageName + ": it does not require the permission android.permission.BIND_MIDI_DEVICE_SERVICE");
                        if (parser != null) {
                            parser.close();
                            return;
                        }
                        return;
                    }
                    ArrayList<String> inputPortNames = new ArrayList<>();
                    ArrayList<String> outputPortNames3 = new ArrayList<>();
                    Bundle properties = null;
                    int numInputPorts = 0;
                    int numOutputPorts = 0;
                    boolean isPrivate = false;
                    while (true) {
                        ArrayList<String> outputPortNames4 = outputPortNames3;
                        int eventType = parser.next();
                        if (eventType == 1) {
                            break;
                        }
                        int i = 0;
                        if (eventType == 2) {
                            String tagName = parser.getName();
                            if ("device".equals(tagName)) {
                                if (properties != null) {
                                    Log.w(TAG, "nested <device> elements in metadata for " + serviceInfo.packageName);
                                    outputPortNames2 = outputPortNames4;
                                    outputPortNames3 = outputPortNames2;
                                    midiService2 = this;
                                } else {
                                    Bundle properties2 = new Bundle();
                                    properties2.putParcelable("service_info", serviceInfo);
                                    boolean isPrivate2 = false;
                                    int count = parser.getAttributeCount();
                                    while (i < count) {
                                        String name = parser.getAttributeName(i);
                                        String value = parser.getAttributeValue(i);
                                        if ("private".equals(name)) {
                                            isPrivate2 = "true".equals(value);
                                        } else {
                                            properties2.putString(name, value);
                                        }
                                        i++;
                                    }
                                    properties = properties2;
                                    numInputPorts = 0;
                                    numOutputPorts = 0;
                                    isPrivate = isPrivate2;
                                    outputPortNames = outputPortNames4;
                                }
                            } else if (!"input-port".equals(tagName)) {
                                if ("output-port".equals(tagName)) {
                                    if (properties == null) {
                                        Log.w(TAG, "<output-port> outside of <device> in metadata for " + serviceInfo.packageName);
                                        outputPortNames2 = outputPortNames4;
                                        outputPortNames3 = outputPortNames2;
                                        midiService2 = this;
                                    } else {
                                        numOutputPorts++;
                                        String portName = null;
                                        int count2 = parser.getAttributeCount();
                                        while (true) {
                                            if (i >= count2) {
                                                break;
                                            }
                                            String name2 = parser.getAttributeName(i);
                                            String value2 = parser.getAttributeValue(i);
                                            if (Settings.ATTR_NAME.equals(name2)) {
                                                portName = value2;
                                                break;
                                            }
                                            i++;
                                        }
                                        outputPortNames4.add(portName);
                                    }
                                }
                                outputPortNames = outputPortNames4;
                            } else if (properties == null) {
                                Log.w(TAG, "<input-port> outside of <device> in metadata for " + serviceInfo.packageName);
                                outputPortNames2 = outputPortNames4;
                                outputPortNames3 = outputPortNames2;
                                midiService2 = this;
                            } else {
                                numInputPorts++;
                                String portName2 = null;
                                int count3 = parser.getAttributeCount();
                                while (true) {
                                    if (i >= count3) {
                                        break;
                                    }
                                    String name3 = parser.getAttributeName(i);
                                    String value3 = parser.getAttributeValue(i);
                                    if (Settings.ATTR_NAME.equals(name3)) {
                                        portName2 = value3;
                                        break;
                                    }
                                    i++;
                                }
                                inputPortNames.add(portName2);
                                outputPortNames = outputPortNames4;
                            }
                            outputPortNames3 = outputPortNames;
                            midiService2 = this;
                        } else {
                            if (eventType != 3 || !"device".equals(parser.getName()) || properties == null) {
                                outputPortNames = outputPortNames4;
                            } else if (numInputPorts == 0 && numOutputPorts == 0) {
                                Log.w(TAG, "<device> with no ports in metadata for " + serviceInfo.packageName);
                                outputPortNames2 = outputPortNames4;
                                outputPortNames3 = outputPortNames2;
                                midiService2 = this;
                            } else {
                                try {
                                    ApplicationInfo appInfo = midiService2.mPackageManager.getApplicationInfo(serviceInfo.packageName, 0);
                                    int uid = appInfo.uid;
                                    HashMap<MidiDeviceInfo, Device> hashMap2 = midiService2.mDevicesByInfo;
                                    synchronized (hashMap2) {
                                        try {
                                            midiService = midiService2;
                                            hashMap = hashMap2;
                                            outputPortNames = outputPortNames4;
                                        } catch (Throwable th) {
                                            th = th;
                                            hashMap = hashMap2;
                                            throw th;
                                        }
                                        try {
                                            midiService.addDeviceLocked(2, numInputPorts, numOutputPorts, (String[]) inputPortNames.toArray(EMPTY_STRING_ARRAY), (String[]) outputPortNames4.toArray(EMPTY_STRING_ARRAY), properties, null, serviceInfo, isPrivate, uid);
                                            properties = null;
                                            inputPortNames.clear();
                                            outputPortNames.clear();
                                        } catch (Throwable th2) {
                                            th = th2;
                                            throw th;
                                        }
                                    }
                                } catch (PackageManager.NameNotFoundException e) {
                                    outputPortNames2 = outputPortNames4;
                                    Log.e(TAG, "could not fetch ApplicationInfo for " + serviceInfo.packageName);
                                }
                            }
                            outputPortNames3 = outputPortNames;
                            midiService2 = this;
                        }
                    }
                    if (parser != null) {
                        parser.close();
                    }
                } catch (Exception e2) {
                    e = e2;
                    parser2 = parser;
                    Log.w(TAG, "Unable to load component info " + serviceInfo.toString(), e);
                    if (parser2 != null) {
                        parser2.close();
                    }
                } catch (Throwable th3) {
                    th = th3;
                    if (parser != null) {
                        parser.close();
                    }
                    throw th;
                }
            } catch (Throwable th4) {
                th = th4;
                parser = parser2;
            }
        } catch (Exception e3) {
            e = e3;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void removePackageDeviceServers(String packageName) {
        synchronized (this.mDevicesByInfo) {
            Iterator<Device> iterator = this.mDevicesByInfo.values().iterator();
            while (iterator.hasNext()) {
                Device device = iterator.next();
                if (packageName.equals(device.getPackageName())) {
                    iterator.remove();
                    removeDeviceLocked(device);
                }
            }
        }
    }

    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        if (DumpUtils.checkDumpPermission(this.mContext, TAG, writer)) {
            IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");
            pw.println("MIDI Manager State:");
            pw.increaseIndent();
            pw.println("Devices:");
            pw.increaseIndent();
            synchronized (this.mDevicesByInfo) {
                for (Device device : this.mDevicesByInfo.values()) {
                    pw.println(device.toString());
                }
            }
            pw.decreaseIndent();
            pw.println("Clients:");
            pw.increaseIndent();
            synchronized (this.mClients) {
                for (Client client : this.mClients.values()) {
                    pw.println(client.toString());
                }
            }
            pw.decreaseIndent();
        }
    }
}
