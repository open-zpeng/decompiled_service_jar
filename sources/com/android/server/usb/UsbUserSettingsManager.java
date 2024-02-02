package com.android.server.usb;

import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.usb.IUsbManager;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.os.Binder;
import android.os.IBinder;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Slog;
import android.util.SparseBooleanArray;
import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.server.pm.PackageManagerService;
import com.xiaopeng.app.xpActivityManager;
import com.xiaopeng.server.aftersales.AfterSalesService;
import java.util.HashMap;
import java.util.Iterator;
/* loaded from: classes.dex */
class UsbUserSettingsManager {
    private static final boolean DEBUG = false;
    private static final String TAG = "UsbUserSettingsManager";
    private final boolean mDisablePermissionDialogs;
    private final PackageManager mPackageManager;
    private final UserHandle mUser;
    private final Context mUserContext;
    private final HashMap<String, SparseBooleanArray> mDevicePermissionMap = new HashMap<>();
    private final HashMap<UsbAccessory, SparseBooleanArray> mAccessoryPermissionMap = new HashMap<>();
    private final Object mLock = new Object();

    public UsbUserSettingsManager(Context context, UserHandle user) {
        try {
            this.mUserContext = context.createPackageContextAsUser(PackageManagerService.PLATFORM_PACKAGE_NAME, 0, user);
            this.mPackageManager = this.mUserContext.getPackageManager();
            this.mUser = user;
            this.mDisablePermissionDialogs = context.getResources().getBoolean(17956932);
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException("Missing android package");
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeDevicePermissions(UsbDevice device) {
        synchronized (this.mLock) {
            this.mDevicePermissionMap.remove(device.getDeviceName());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeAccessoryPermissions(UsbAccessory accessory) {
        synchronized (this.mLock) {
            this.mAccessoryPermissionMap.remove(accessory);
        }
    }

    private boolean isCameraDevicePresent(UsbDevice device) {
        if (device.getDeviceClass() == 14) {
            return true;
        }
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getInterfaceClass() == 14) {
                return true;
            }
        }
        return false;
    }

    private boolean isCameraPermissionGranted(String packageName, int uid) {
        try {
            ApplicationInfo aInfo = this.mPackageManager.getApplicationInfo(packageName, 0);
            if (aInfo.uid != uid) {
                Slog.i(TAG, "Package " + packageName + " does not match caller's uid " + uid);
                return false;
            }
            int targetSdkVersion = aInfo.targetSdkVersion;
            if (targetSdkVersion >= 28) {
                int allowed = this.mUserContext.checkCallingPermission("android.permission.CAMERA");
                if (-1 == allowed) {
                    Slog.i(TAG, "Camera permission required for USB video class devices");
                    return false;
                }
                return true;
            }
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            Slog.i(TAG, "Package not found, likely due to invalid package name!");
            return false;
        }
    }

    public boolean hasPermission(UsbDevice device, String packageName, int uid) {
        synchronized (this.mLock) {
            if (!isCameraDevicePresent(device) || isCameraPermissionGranted(packageName, uid)) {
                if (uid != 1000 && !this.mDisablePermissionDialogs) {
                    SparseBooleanArray uidList = this.mDevicePermissionMap.get(device.getDeviceName());
                    if (uidList == null) {
                        return false;
                    }
                    return uidList.get(uid);
                }
                return true;
            }
            return false;
        }
    }

    public boolean hasPermission(UsbAccessory accessory) {
        synchronized (this.mLock) {
            int uid = Binder.getCallingUid();
            if (uid != 1000 && !this.mDisablePermissionDialogs) {
                SparseBooleanArray uidList = this.mAccessoryPermissionMap.get(accessory);
                if (uidList == null) {
                    return false;
                }
                return uidList.get(uid);
            }
            return true;
        }
    }

    public void checkPermission(UsbDevice device, String packageName, int uid) {
        if (!hasPermission(device, packageName, uid)) {
            throw new SecurityException("User has not given permission to device " + device);
        }
    }

    public void checkPermission(UsbAccessory accessory) {
        if (!hasPermission(accessory)) {
            throw new SecurityException("User has not given permission to accessory " + accessory);
        }
    }

    private void requestPermissionDialog(Intent intent, String packageName, PendingIntent pi) {
        int uid = Binder.getCallingUid();
        try {
            ApplicationInfo aInfo = this.mPackageManager.getApplicationInfo(packageName, 0);
            if (aInfo.uid != uid) {
                throw new IllegalArgumentException("package " + packageName + " does not match caller's uid " + uid);
            }
            long identity = Binder.clearCallingIdentity();
            intent.setClassName(AfterSalesService.PackgeName.SYSTEMUI, "com.android.systemui.usb.UsbPermissionActivity");
            intent.addFlags(268435456);
            intent.putExtra("android.intent.extra.INTENT", pi);
            intent.putExtra("package", packageName);
            intent.putExtra("android.intent.extra.UID", uid);
            try {
                try {
                    this.mUserContext.startActivityAsUser(intent, this.mUser);
                } catch (ActivityNotFoundException e) {
                    Slog.e(TAG, "unable to start UsbPermissionActivity");
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        } catch (PackageManager.NameNotFoundException e2) {
            throw new IllegalArgumentException("package " + packageName + " not found");
        }
    }

    public void requestPermission(UsbDevice device, String packageName, PendingIntent pi, int uid) {
        Intent intent = new Intent();
        if (xpActivityManager.shouldGrantUsbPermission(packageName)) {
            try {
                IBinder b = ServiceManager.getService("usb");
                IUsbManager service = IUsbManager.Stub.asInterface(b);
                grantDevicePermission(device, uid);
                service.setDevicePackage(device, packageName, uid);
                Slog.i(TAG, "grantDevicePermission for packageName=" + packageName);
            } catch (Exception e) {
            }
        }
        if (hasPermission(device, packageName, uid)) {
            intent.putExtra("device", device);
            intent.putExtra("permission", true);
            try {
                pi.send(this.mUserContext, 0, intent);
            } catch (PendingIntent.CanceledException e2) {
            }
        } else if (isCameraDevicePresent(device) && !isCameraPermissionGranted(packageName, uid)) {
            intent.putExtra("device", device);
            intent.putExtra("permission", false);
            try {
                pi.send(this.mUserContext, 0, intent);
            } catch (PendingIntent.CanceledException e3) {
            }
        } else {
            intent.putExtra("device", device);
            requestPermissionDialog(intent, packageName, pi);
        }
    }

    public void requestPermission(UsbAccessory accessory, String packageName, PendingIntent pi) {
        if (xpActivityManager.shouldGrantUsbPermission(packageName)) {
            try {
                int uid = Binder.getCallingUid();
                try {
                    ApplicationInfo aInfo = this.mPackageManager.getApplicationInfo(packageName, 0);
                    uid = aInfo != null ? aInfo.uid : uid;
                } catch (PackageManager.NameNotFoundException e) {
                }
                IBinder b = ServiceManager.getService("usb");
                IUsbManager service = IUsbManager.Stub.asInterface(b);
                grantAccessoryPermission(accessory, uid);
                service.setAccessoryPackage(accessory, packageName, uid);
                Slog.i(TAG, "grantDevicePermission for packageName=" + packageName);
            } catch (Exception e2) {
            }
        }
        Intent intent = new Intent();
        if (hasPermission(accessory)) {
            intent.putExtra("accessory", accessory);
            intent.putExtra("permission", true);
            try {
                pi.send(this.mUserContext, 0, intent);
                return;
            } catch (PendingIntent.CanceledException e3) {
                return;
            }
        }
        intent.putExtra("accessory", accessory);
        requestPermissionDialog(intent, packageName, pi);
    }

    public void grantDevicePermission(UsbDevice device, int uid) {
        synchronized (this.mLock) {
            String deviceName = device.getDeviceName();
            SparseBooleanArray uidList = this.mDevicePermissionMap.get(deviceName);
            if (uidList == null) {
                uidList = new SparseBooleanArray(1);
                this.mDevicePermissionMap.put(deviceName, uidList);
            }
            uidList.put(uid, true);
        }
    }

    public void grantAccessoryPermission(UsbAccessory accessory, int uid) {
        synchronized (this.mLock) {
            SparseBooleanArray uidList = this.mAccessoryPermissionMap.get(accessory);
            if (uidList == null) {
                uidList = new SparseBooleanArray(1);
                this.mAccessoryPermissionMap.put(accessory, uidList);
            }
            uidList.put(uid, true);
        }
    }

    public void dump(DualDumpOutputStream dump, String idName, long id) {
        long j;
        long token = dump.start(idName, id);
        synchronized (this.mLock) {
            dump.write("user_id", 1120986464257L, this.mUser.getIdentifier());
            Iterator<String> it = this.mDevicePermissionMap.keySet().iterator();
            while (true) {
                j = 1138166333441L;
                if (!it.hasNext()) {
                    break;
                }
                String deviceName = it.next();
                long devicePermissionToken = dump.start("device_permissions", 2246267895810L);
                dump.write("device_name", 1138166333441L, deviceName);
                SparseBooleanArray uidList = this.mDevicePermissionMap.get(deviceName);
                int count = uidList.size();
                for (int i = 0; i < count; i++) {
                    dump.write("uids", 2220498092034L, uidList.keyAt(i));
                }
                dump.end(devicePermissionToken);
            }
            for (UsbAccessory accessory : this.mAccessoryPermissionMap.keySet()) {
                long accessoryPermissionToken = dump.start("accessory_permissions", 2246267895811L);
                dump.write("accessory_description", j, accessory.getDescription());
                SparseBooleanArray uidList2 = this.mAccessoryPermissionMap.get(accessory);
                int count2 = uidList2.size();
                int i2 = 0;
                while (true) {
                    int i3 = i2;
                    if (i3 < count2) {
                        dump.write("uids", 2220498092034L, uidList2.keyAt(i3));
                        i2 = i3 + 1;
                    }
                }
                dump.end(accessoryPermissionToken);
                j = 1138166333441L;
            }
        }
        dump.end(token);
    }
}
