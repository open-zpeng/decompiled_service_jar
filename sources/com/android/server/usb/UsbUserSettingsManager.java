package com.android.server.usb;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.XmlResourceParser;
import android.hardware.usb.AccessoryFilter;
import android.hardware.usb.DeviceFilter;
import android.hardware.usb.IUsbManager;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.os.IBinder;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Slog;
import com.android.internal.util.XmlUtils;
import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.internal.util.dump.DumpUtils;
import com.android.server.pm.PackageManagerService;
import com.android.server.slice.SliceClientPermissions;
import com.xiaopeng.app.xpActivityManager;
import java.util.ArrayList;
import java.util.List;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public class UsbUserSettingsManager {
    private static final boolean DEBUG = false;
    private static final String TAG = UsbUserSettingsManager.class.getSimpleName();
    private final Object mLock = new Object();
    private final PackageManager mPackageManager;
    private final UsbPermissionManager mUsbPermissionManager;
    private final UserHandle mUser;
    private final Context mUserContext;

    /* JADX INFO: Access modifiers changed from: package-private */
    public UsbUserSettingsManager(Context context, UserHandle user, UsbPermissionManager usbPermissionManager) {
        try {
            this.mUserContext = context.createPackageContextAsUser(PackageManagerService.PLATFORM_PACKAGE_NAME, 0, user);
            this.mPackageManager = this.mUserContext.getPackageManager();
            this.mUser = user;
            this.mUsbPermissionManager = usbPermissionManager;
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException("Missing android package");
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeDevicePermissions(UsbDevice device) {
        this.mUsbPermissionManager.removeDevicePermissions(device);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeAccessoryPermissions(UsbAccessory accessory) {
        this.mUsbPermissionManager.removeAccessoryPermissions(accessory);
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

    private boolean isCameraPermissionGranted(String packageName, int pid, int uid) {
        try {
            ApplicationInfo aInfo = this.mPackageManager.getApplicationInfo(packageName, 0);
            if (aInfo.uid != uid) {
                String str = TAG;
                Slog.i(str, "Package " + packageName + " does not match caller's uid " + uid);
                return false;
            }
            int targetSdkVersion = aInfo.targetSdkVersion;
            if (targetSdkVersion >= 28) {
                int allowed = this.mUserContext.checkPermission("android.permission.CAMERA", pid, uid);
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

    public boolean hasPermission(UsbDevice device, String packageName, int pid, int uid) {
        if (isCameraDevicePresent(device) && !isCameraPermissionGranted(packageName, pid, uid)) {
            return false;
        }
        return this.mUsbPermissionManager.hasPermission(device, uid);
    }

    public boolean hasPermission(UsbAccessory accessory, int uid) {
        return this.mUsbPermissionManager.hasPermission(accessory, uid);
    }

    public void checkPermission(UsbDevice device, String packageName, int pid, int uid) {
        if (!hasPermission(device, packageName, pid, uid)) {
            throw new SecurityException("User has not given " + uid + SliceClientPermissions.SliceAuthority.DELIMITER + packageName + " permission to access device " + device.getDeviceName());
        }
    }

    public void checkPermission(UsbAccessory accessory, int uid) {
        if (!hasPermission(accessory, uid)) {
            throw new SecurityException("User has not given " + uid + " permission to accessory " + accessory);
        }
    }

    private void requestPermissionDialog(UsbDevice device, UsbAccessory accessory, boolean canBeDefault, String packageName, PendingIntent pi, int uid) {
        try {
            ApplicationInfo aInfo = this.mPackageManager.getApplicationInfo(packageName, 0);
            if (aInfo.uid == uid) {
                this.mUsbPermissionManager.requestPermissionDialog(device, accessory, canBeDefault, packageName, uid, this.mUserContext, pi);
                return;
            }
            throw new IllegalArgumentException("package " + packageName + " does not match caller's uid " + uid);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalArgumentException("package " + packageName + " not found");
        }
    }

    public void requestPermission(UsbDevice device, String packageName, PendingIntent pi, int pid, int uid) {
        Intent intent = new Intent();
        if (xpActivityManager.shouldGrantUsbPermission(packageName)) {
            try {
                IBinder b = ServiceManager.getService("usb");
                IUsbManager service = IUsbManager.Stub.asInterface(b);
                grantDevicePermission(device, uid);
                service.setDevicePackage(device, packageName, uid);
                String str = TAG;
                Slog.i(str, "grantDevicePermission for packageName=" + packageName);
            } catch (Exception e) {
            }
        }
        if (hasPermission(device, packageName, pid, uid)) {
            intent.putExtra("device", device);
            intent.putExtra("permission", true);
            try {
                pi.send(this.mUserContext, 0, intent);
            } catch (PendingIntent.CanceledException e2) {
            }
        } else if (isCameraDevicePresent(device) && !isCameraPermissionGranted(packageName, pid, uid)) {
            intent.putExtra("device", device);
            intent.putExtra("permission", false);
            try {
                pi.send(this.mUserContext, 0, intent);
            } catch (PendingIntent.CanceledException e3) {
            }
        } else {
            requestPermissionDialog(device, null, canBeDefault(device, packageName), packageName, pi, uid);
        }
    }

    public void requestPermission(UsbAccessory accessory, String packageName, PendingIntent pi, int uid) {
        if (xpActivityManager.shouldGrantUsbPermission(packageName)) {
            try {
                IBinder b = ServiceManager.getService("usb");
                IUsbManager service = IUsbManager.Stub.asInterface(b);
                grantAccessoryPermission(accessory, uid);
                service.setAccessoryPackage(accessory, packageName, uid);
                String str = TAG;
                Slog.i(str, "grantAccessoryPermission for packageName=" + packageName);
            } catch (Exception e) {
            }
        }
        if (hasPermission(accessory, uid)) {
            Intent intent = new Intent();
            intent.putExtra("accessory", accessory);
            intent.putExtra("permission", true);
            try {
                pi.send(this.mUserContext, 0, intent);
                return;
            } catch (PendingIntent.CanceledException e2) {
                return;
            }
        }
        requestPermissionDialog(null, accessory, canBeDefault(accessory, packageName), packageName, pi, uid);
    }

    public void grantDevicePermission(UsbDevice device, int uid) {
        this.mUsbPermissionManager.grantDevicePermission(device, uid);
    }

    public void grantAccessoryPermission(UsbAccessory accessory, int uid) {
        this.mUsbPermissionManager.grantAccessoryPermission(accessory, uid);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public List<ResolveInfo> queryIntentActivities(Intent intent) {
        return this.mPackageManager.queryIntentActivitiesAsUser(intent, 128, this.mUser.getIdentifier());
    }

    private boolean canBeDefault(UsbDevice device, String packageName) {
        ActivityInfo[] activities = getPackageActivities(packageName);
        if (activities != null) {
            for (ActivityInfo activityInfo : activities) {
                try {
                    XmlResourceParser parser = activityInfo.loadXmlMetaData(this.mPackageManager, "android.hardware.usb.action.USB_DEVICE_ATTACHED");
                    if (parser != null) {
                        XmlUtils.nextElement(parser);
                        while (parser.getEventType() != 1) {
                            if ("usb-device".equals(parser.getName())) {
                                DeviceFilter filter = DeviceFilter.read(parser);
                                if (filter.matches(device)) {
                                    $closeResource(null, parser);
                                    return true;
                                }
                            }
                            XmlUtils.nextElement(parser);
                        }
                        $closeResource(null, parser);
                    } else if (parser != null) {
                        $closeResource(null, parser);
                    }
                } catch (Exception e) {
                    Slog.w(TAG, "Unable to load component info " + activityInfo.toString(), e);
                }
            }
            return false;
        }
        return false;
    }

    private static /* synthetic */ void $closeResource(Throwable x0, AutoCloseable x1) {
        if (x0 == null) {
            x1.close();
            return;
        }
        try {
            x1.close();
        } catch (Throwable th) {
            x0.addSuppressed(th);
        }
    }

    private boolean canBeDefault(UsbAccessory accessory, String packageName) {
        ActivityInfo[] activities = getPackageActivities(packageName);
        if (activities != null) {
            for (ActivityInfo activityInfo : activities) {
                try {
                    XmlResourceParser parser = activityInfo.loadXmlMetaData(this.mPackageManager, "android.hardware.usb.action.USB_ACCESSORY_ATTACHED");
                    if (parser != null) {
                        XmlUtils.nextElement(parser);
                        while (parser.getEventType() != 1) {
                            if ("usb-accessory".equals(parser.getName())) {
                                AccessoryFilter filter = AccessoryFilter.read(parser);
                                if (filter.matches(accessory)) {
                                    $closeResource(null, parser);
                                    return true;
                                }
                            }
                            XmlUtils.nextElement(parser);
                        }
                        $closeResource(null, parser);
                    } else if (parser != null) {
                        $closeResource(null, parser);
                    }
                } catch (Exception e) {
                    Slog.w(TAG, "Unable to load component info " + activityInfo.toString(), e);
                }
            }
            return false;
        }
        return false;
    }

    private ActivityInfo[] getPackageActivities(String packageName) {
        try {
            PackageInfo packageInfo = this.mPackageManager.getPackageInfo(packageName, 129);
            return packageInfo.activities;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    public void dump(DualDumpOutputStream dump, String idName, long id) {
        int numDeviceAttachedActivities;
        long token = dump.start(idName, id);
        synchronized (this.mLock) {
            dump.write("user_id", 1120986464257L, this.mUser.getIdentifier());
            this.mUsbPermissionManager.dump(dump);
            List<ResolveInfo> deviceAttachedActivities = queryIntentActivities(new Intent("android.hardware.usb.action.USB_DEVICE_ATTACHED"));
            int numDeviceAttachedActivities2 = deviceAttachedActivities.size();
            for (int activityNum = 0; activityNum < numDeviceAttachedActivities2; activityNum++) {
                ResolveInfo deviceAttachedActivity = deviceAttachedActivities.get(activityNum);
                long deviceAttachedActivityToken = dump.start("device_attached_activities", 2246267895812L);
                DumpUtils.writeComponentName(dump, "activity", 1146756268033L, new ComponentName(deviceAttachedActivity.activityInfo.packageName, deviceAttachedActivity.activityInfo.name));
                ArrayList<DeviceFilter> deviceFilters = UsbProfileGroupSettingsManager.getDeviceFilters(this.mPackageManager, deviceAttachedActivity);
                if (deviceFilters != null) {
                    int filterNum = 0;
                    for (int numDeviceFilters = deviceFilters.size(); filterNum < numDeviceFilters; numDeviceFilters = numDeviceFilters) {
                        deviceFilters.get(filterNum).dump(dump, "filters", 2246267895810L);
                        filterNum++;
                        deviceFilters = deviceFilters;
                    }
                }
                dump.end(deviceAttachedActivityToken);
            }
            List<ResolveInfo> accessoryAttachedActivities = queryIntentActivities(new Intent("android.hardware.usb.action.USB_ACCESSORY_ATTACHED"));
            int numAccessoryAttachedActivities = accessoryAttachedActivities.size();
            int activityNum2 = 0;
            while (activityNum2 < numAccessoryAttachedActivities) {
                ResolveInfo accessoryAttachedActivity = accessoryAttachedActivities.get(activityNum2);
                long accessoryAttachedActivityToken = dump.start("accessory_attached_activities", 2246267895813L);
                List<ResolveInfo> deviceAttachedActivities2 = deviceAttachedActivities;
                int numDeviceAttachedActivities3 = numDeviceAttachedActivities2;
                List<ResolveInfo> accessoryAttachedActivities2 = accessoryAttachedActivities;
                DumpUtils.writeComponentName(dump, "activity", 1146756268033L, new ComponentName(accessoryAttachedActivity.activityInfo.packageName, accessoryAttachedActivity.activityInfo.name));
                ArrayList<AccessoryFilter> accessoryFilters = UsbProfileGroupSettingsManager.getAccessoryFilters(this.mPackageManager, accessoryAttachedActivity);
                if (accessoryFilters == null) {
                    numDeviceAttachedActivities = numDeviceAttachedActivities3;
                } else {
                    int filterNum2 = 0;
                    for (int numAccessoryFilters = accessoryFilters.size(); filterNum2 < numAccessoryFilters; numAccessoryFilters = numAccessoryFilters) {
                        accessoryFilters.get(filterNum2).dump(dump, "filters", 2246267895810L);
                        filterNum2++;
                        numDeviceAttachedActivities3 = numDeviceAttachedActivities3;
                        accessoryFilters = accessoryFilters;
                    }
                    numDeviceAttachedActivities = numDeviceAttachedActivities3;
                }
                dump.end(accessoryAttachedActivityToken);
                activityNum2++;
                accessoryAttachedActivities = accessoryAttachedActivities2;
                numDeviceAttachedActivities2 = numDeviceAttachedActivities;
                deviceAttachedActivities = deviceAttachedActivities2;
            }
        }
        dump.end(token);
    }
}
