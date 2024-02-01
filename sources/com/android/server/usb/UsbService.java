package com.android.server.usb;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.IUsbManager;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbPort;
import android.hardware.usb.UsbPortStatus;
import android.os.Binder;
import android.os.Bundle;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Slog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;
import com.android.server.SystemService;
import java.io.File;
import java.io.IOException;
/* loaded from: classes.dex */
public class UsbService extends IUsbManager.Stub {
    private static final int HANDLE_USB_PORT_NUM = 0;
    private static final String PROPERTY_XP_USB_MODE = "sys.xiaopeng.usb.debug";
    private static final String TAG = "UsbService";
    private static final String USB_MODE_CONFIG = "/sys/devices/soc/6a00000.ssusb/mode";
    private static final String USB_MODE_HOST = "false";
    private static final String USB_MODE_NONE = "none";
    private static final String USB_PERIPHERAL = "peripheral";
    private static boolean mNeedHandleUSBProt = false;
    private static int mRetryNum = 0;
    private final UsbAlsaManager mAlsaManager;
    private final Context mContext;
    @GuardedBy("mLock")
    private int mCurrentUserId;
    private UsbDeviceManager mDeviceManager;
    private UsbHostManager mHostManager;
    private final Object mLock = new Object();
    private UsbPortManager mPortManager;
    private final UsbSettingsManager mSettingsManager;
    private final UserManager mUserManager;

    /* loaded from: classes.dex */
    public static class Lifecycle extends SystemService {
        private UsbService mUsbService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override // com.android.server.SystemService
        public void onStart() {
            this.mUsbService = new UsbService(getContext());
            publishBinderService("usb", this.mUsbService);
        }

        @Override // com.android.server.SystemService
        public void onBootPhase(int phase) {
            if (phase == 550) {
                this.mUsbService.systemReady();
            } else if (phase == 1000) {
                this.mUsbService.bootCompleted();
                this.mUsbService.handleUSBPort();
            }
        }

        @Override // com.android.server.SystemService
        public void onSwitchUser(int newUserId) {
            this.mUsbService.onSwitchUser(newUserId);
        }

        @Override // com.android.server.SystemService
        public void onStopUser(int userHandle) {
            this.mUsbService.onStopUser(UserHandle.of(userHandle));
        }

        @Override // com.android.server.SystemService
        public void onUnlockUser(int userHandle) {
            this.mUsbService.onUnlockUser(userHandle);
        }
    }

    private UsbUserSettingsManager getSettingsForUser(int userIdInt) {
        return this.mSettingsManager.getSettingsForUser(userIdInt);
    }

    public UsbService(Context context) {
        this.mContext = context;
        this.mUserManager = (UserManager) context.getSystemService(UserManager.class);
        this.mSettingsManager = new UsbSettingsManager(context);
        this.mAlsaManager = new UsbAlsaManager(context);
        PackageManager pm = this.mContext.getPackageManager();
        if (pm.hasSystemFeature("android.hardware.usb.host")) {
            this.mHostManager = new UsbHostManager(context, this.mAlsaManager, this.mSettingsManager);
        }
        if (new File("/sys/class/android_usb").exists()) {
            this.mDeviceManager = new UsbDeviceManager(context, this.mAlsaManager, this.mSettingsManager);
        }
        if (this.mHostManager != null || this.mDeviceManager != null) {
            this.mPortManager = new UsbPortManager(context);
        }
        onSwitchUser(0);
        BroadcastReceiver receiver = new BroadcastReceiver() { // from class: com.android.server.usb.UsbService.1
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                String action = intent.getAction();
                if ("android.app.action.DEVICE_POLICY_MANAGER_STATE_CHANGED".equals(action) && UsbService.this.mDeviceManager != null) {
                    UsbService.this.mDeviceManager.updateUserRestrictions();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.setPriority(1000);
        filter.addAction("android.app.action.DEVICE_POLICY_MANAGER_STATE_CHANGED");
        this.mContext.registerReceiver(receiver, filter, null, null);
        BroadcastReceiver receiverScreenOff = new BroadcastReceiver() { // from class: com.android.server.usb.UsbService.2
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                if ("android.intent.action.SCREEN_OFF".equals(intent.getAction())) {
                    boolean unused = UsbService.mNeedHandleUSBProt = UsbService.this.mHostManager.isDeviceMount();
                }
            }
        };
        IntentFilter filterScreenOff = new IntentFilter();
        filterScreenOff.addAction("android.intent.action.SCREEN_OFF");
        this.mContext.registerReceiver(receiverScreenOff, filterScreenOff);
        BroadcastReceiver receiverScreenOn = new BroadcastReceiver() { // from class: com.android.server.usb.UsbService.3
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                if ("android.intent.action.SCREEN_ON".equals(intent.getAction()) && UsbService.mNeedHandleUSBProt) {
                    SystemClock.sleep(6000L);
                    UsbService.this.handleUSBPort();
                }
            }
        };
        IntentFilter filteScreenOn = new IntentFilter();
        filteScreenOn.addAction("android.intent.action.SCREEN_ON");
        this.mContext.registerReceiver(receiverScreenOn, filteScreenOn);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onSwitchUser(int newUserId) {
        synchronized (this.mLock) {
            this.mCurrentUserId = newUserId;
            UsbProfileGroupSettingsManager settings = this.mSettingsManager.getSettingsForProfileGroup(UserHandle.of(newUserId));
            if (this.mHostManager != null) {
                this.mHostManager.setCurrentUserSettings(settings);
            }
            if (this.mDeviceManager != null) {
                this.mDeviceManager.setCurrentUser(newUserId, settings);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onStopUser(UserHandle stoppedUser) {
        this.mSettingsManager.remove(stoppedUser);
    }

    public void systemReady() {
        this.mAlsaManager.systemReady();
        if (this.mDeviceManager != null) {
            this.mDeviceManager.systemReady();
        }
        if (this.mHostManager != null) {
            this.mHostManager.systemReady();
        }
        if (this.mPortManager != null) {
            this.mPortManager.systemReady();
        }
    }

    public void bootCompleted() {
        if (this.mDeviceManager != null) {
            this.mDeviceManager.bootCompleted();
        }
    }

    public boolean handleUSBPort() {
        String mode = null;
        try {
            mode = FileUtils.readTextFile(new File(USB_MODE_CONFIG), USB_PERIPHERAL.length(), null);
        } catch (IOException e) {
            Slog.w(TAG, "handleUSBPort --> " + e.getMessage());
        }
        if (USB_PERIPHERAL.equals(mode)) {
            Slog.w(TAG, "USB is debug mode, unneed handle USB prot.");
            mRetryNum = 0;
            return true;
        }
        boolean isDeviceMount = this.mHostManager.isDeviceMount();
        if (isDeviceMount) {
            Slog.w(TAG, "USB mount some device.");
            mRetryNum = 0;
            return true;
        } else if (mRetryNum > 0) {
            Slog.e(TAG, "Mount USB HUB retry number above " + mRetryNum);
            mRetryNum = 0;
            return false;
        } else {
            mRetryNum++;
            Slog.w(TAG, "USB port unmounted everyone, retry to mount. retry number " + mRetryNum);
            SystemProperties.set(PROPERTY_XP_USB_MODE, USB_MODE_NONE);
            SystemClock.sleep(1000L);
            SystemProperties.set(PROPERTY_XP_USB_MODE, USB_MODE_HOST);
            SystemClock.sleep(1000L);
            return handleUSBPort();
        }
    }

    public void onUnlockUser(int user) {
        if (this.mDeviceManager != null) {
            this.mDeviceManager.onUnlockUser(user);
        }
    }

    public void getDeviceList(Bundle devices) {
        if (this.mHostManager != null) {
            this.mHostManager.getDeviceList(devices);
        }
    }

    @GuardedBy("mLock")
    private boolean isCallerInCurrentUserProfileGroupLocked() {
        int userIdInt = UserHandle.getCallingUserId();
        long ident = clearCallingIdentity();
        try {
            return this.mUserManager.isSameProfileGroup(userIdInt, this.mCurrentUserId);
        } finally {
            restoreCallingIdentity(ident);
        }
    }

    public ParcelFileDescriptor openDevice(String deviceName, String packageName) {
        ParcelFileDescriptor fd = null;
        if (this.mHostManager != null) {
            synchronized (this.mLock) {
                if (deviceName != null) {
                    try {
                        int userIdInt = UserHandle.getCallingUserId();
                        boolean isCurrentUser = isCallerInCurrentUserProfileGroupLocked();
                        if (isCurrentUser) {
                            fd = this.mHostManager.openDevice(deviceName, getSettingsForUser(userIdInt), packageName, Binder.getCallingUid());
                        } else {
                            Slog.w(TAG, "Cannot open " + deviceName + " for user " + userIdInt + " as user is not active.");
                        }
                    } finally {
                    }
                }
            }
        }
        return fd;
    }

    public UsbAccessory getCurrentAccessory() {
        if (this.mDeviceManager != null) {
            return this.mDeviceManager.getCurrentAccessory();
        }
        return null;
    }

    public ParcelFileDescriptor openAccessory(UsbAccessory accessory) {
        if (this.mDeviceManager != null) {
            int userIdInt = UserHandle.getCallingUserId();
            synchronized (this.mLock) {
                boolean isCurrentUser = isCallerInCurrentUserProfileGroupLocked();
                if (isCurrentUser) {
                    return this.mDeviceManager.openAccessory(accessory, getSettingsForUser(userIdInt));
                }
                Slog.w(TAG, "Cannot open " + accessory + " for user " + userIdInt + " as user is not active.");
                return null;
            }
        }
        return null;
    }

    public ParcelFileDescriptor getControlFd(long function) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.ACCESS_MTP", null);
        return this.mDeviceManager.getControlFd(function);
    }

    public void setDevicePackage(UsbDevice device, String packageName, int userId) {
        UsbDevice device2 = (UsbDevice) Preconditions.checkNotNull(device);
        this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_USB", null);
        UserHandle user = UserHandle.of(userId);
        this.mSettingsManager.getSettingsForProfileGroup(user).setDevicePackage(device2, packageName, user);
    }

    public void setAccessoryPackage(UsbAccessory accessory, String packageName, int userId) {
        UsbAccessory accessory2 = (UsbAccessory) Preconditions.checkNotNull(accessory);
        this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_USB", null);
        UserHandle user = UserHandle.of(userId);
        this.mSettingsManager.getSettingsForProfileGroup(user).setAccessoryPackage(accessory2, packageName, user);
    }

    public boolean hasDevicePermission(UsbDevice device, String packageName) {
        int userId = UserHandle.getCallingUserId();
        return getSettingsForUser(userId).hasPermission(device, packageName, Binder.getCallingUid());
    }

    public boolean hasAccessoryPermission(UsbAccessory accessory) {
        int userId = UserHandle.getCallingUserId();
        return getSettingsForUser(userId).hasPermission(accessory);
    }

    public void requestDevicePermission(UsbDevice device, String packageName, PendingIntent pi) {
        int userId = UserHandle.getCallingUserId();
        getSettingsForUser(userId).requestPermission(device, packageName, pi, Binder.getCallingUid());
    }

    public void requestAccessoryPermission(UsbAccessory accessory, String packageName, PendingIntent pi) {
        int userId = UserHandle.getCallingUserId();
        getSettingsForUser(userId).requestPermission(accessory, packageName, pi);
    }

    public void grantDevicePermission(UsbDevice device, int uid) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_USB", null);
        int userId = UserHandle.getUserId(uid);
        getSettingsForUser(userId).grantDevicePermission(device, uid);
    }

    public void grantAccessoryPermission(UsbAccessory accessory, int uid) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_USB", null);
        int userId = UserHandle.getUserId(uid);
        getSettingsForUser(userId).grantAccessoryPermission(accessory, uid);
    }

    public boolean hasDefaults(String packageName, int userId) {
        String packageName2 = (String) Preconditions.checkStringNotEmpty(packageName);
        this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_USB", null);
        UserHandle user = UserHandle.of(userId);
        return this.mSettingsManager.getSettingsForProfileGroup(user).hasDefaults(packageName2, user);
    }

    public void clearDefaults(String packageName, int userId) {
        String packageName2 = (String) Preconditions.checkStringNotEmpty(packageName);
        this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_USB", null);
        UserHandle user = UserHandle.of(userId);
        this.mSettingsManager.getSettingsForProfileGroup(user).clearDefaults(packageName2, user);
    }

    public void setCurrentFunctions(long functions) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_USB", null);
        Preconditions.checkArgument(UsbManager.areSettableFunctions(functions));
        Preconditions.checkState(this.mDeviceManager != null);
        this.mDeviceManager.setCurrentFunctions(functions);
    }

    public void setCurrentFunction(String functions, boolean usbDataUnlocked) {
        setCurrentFunctions(UsbManager.usbFunctionsFromString(functions));
    }

    public boolean isFunctionEnabled(String function) {
        return (getCurrentFunctions() & UsbManager.usbFunctionsFromString(function)) != 0;
    }

    public long getCurrentFunctions() {
        this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_USB", null);
        Preconditions.checkState(this.mDeviceManager != null);
        return this.mDeviceManager.getCurrentFunctions();
    }

    public void setScreenUnlockedFunctions(long functions) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_USB", null);
        Preconditions.checkArgument(UsbManager.areSettableFunctions(functions));
        Preconditions.checkState(this.mDeviceManager != null);
        this.mDeviceManager.setScreenUnlockedFunctions(functions);
    }

    public long getScreenUnlockedFunctions() {
        this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_USB", null);
        Preconditions.checkState(this.mDeviceManager != null);
        return this.mDeviceManager.getScreenUnlockedFunctions();
    }

    public void allowUsbDebugging(boolean alwaysAllow, String publicKey) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_USB", null);
        this.mDeviceManager.allowUsbDebugging(alwaysAllow, publicKey);
    }

    public void denyUsbDebugging() {
        this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_USB", null);
        this.mDeviceManager.denyUsbDebugging();
    }

    public void clearUsbDebuggingKeys() {
        this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_USB", null);
        this.mDeviceManager.clearUsbDebuggingKeys();
    }

    public UsbPort[] getPorts() {
        this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_USB", null);
        long ident = Binder.clearCallingIdentity();
        try {
            return this.mPortManager != null ? this.mPortManager.getPorts() : null;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public UsbPortStatus getPortStatus(String portId) {
        Preconditions.checkNotNull(portId, "portId must not be null");
        this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_USB", null);
        long ident = Binder.clearCallingIdentity();
        try {
            return this.mPortManager != null ? this.mPortManager.getPortStatus(portId) : null;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public void setPortRoles(String portId, int powerRole, int dataRole) {
        Preconditions.checkNotNull(portId, "portId must not be null");
        UsbPort.checkRoles(powerRole, dataRole);
        this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_USB", null);
        long ident = Binder.clearCallingIdentity();
        try {
            if (this.mPortManager != null) {
                this.mPortManager.setPortRoles(portId, powerRole, dataRole, null);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public void setUsbDeviceConnectionHandler(ComponentName usbDeviceConnectionHandler) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_USB", null);
        synchronized (this.mLock) {
            if (this.mCurrentUserId == UserHandle.getCallingUserId()) {
                if (this.mHostManager != null) {
                    this.mHostManager.setUsbDeviceConnectionHandler(usbDeviceConnectionHandler);
                }
            } else {
                throw new IllegalArgumentException("Only the current user can register a usb connection handler");
            }
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:105:0x019c A[Catch: all -> 0x0136, TryCatch #2 {all -> 0x0136, blocks: (B:20:0x005e, B:22:0x0061, B:39:0x0098, B:40:0x009b, B:73:0x011e, B:44:0x00a5, B:61:0x00d8, B:62:0x00db, B:70:0x0106, B:66:0x00e4, B:68:0x00e9, B:51:0x00b9, B:54:0x00c3, B:57:0x00cd, B:29:0x0076, B:32:0x0081, B:35:0x008c, B:81:0x014d, B:83:0x0150, B:104:0x0199, B:105:0x019c, B:115:0x01cd, B:110:0x01a7, B:112:0x01ac, B:91:0x0169, B:94:0x0175, B:97:0x0180, B:100:0x018c, B:125:0x0207, B:132:0x0219, B:149:0x0249, B:157:0x025e, B:174:0x0290, B:183:0x02a8), top: B:288:0x005e }] */
    /* JADX WARN: Removed duplicated region for block: B:106:0x019f  */
    /* JADX WARN: Removed duplicated region for block: B:107:0x01a1  */
    /* JADX WARN: Removed duplicated region for block: B:108:0x01a3  */
    /* JADX WARN: Removed duplicated region for block: B:109:0x01a5  */
    /* JADX WARN: Removed duplicated region for block: B:112:0x01ac A[Catch: all -> 0x0136, TryCatch #2 {all -> 0x0136, blocks: (B:20:0x005e, B:22:0x0061, B:39:0x0098, B:40:0x009b, B:73:0x011e, B:44:0x00a5, B:61:0x00d8, B:62:0x00db, B:70:0x0106, B:66:0x00e4, B:68:0x00e9, B:51:0x00b9, B:54:0x00c3, B:57:0x00cd, B:29:0x0076, B:32:0x0081, B:35:0x008c, B:81:0x014d, B:83:0x0150, B:104:0x0199, B:105:0x019c, B:115:0x01cd, B:110:0x01a7, B:112:0x01ac, B:91:0x0169, B:94:0x0175, B:97:0x0180, B:100:0x018c, B:125:0x0207, B:132:0x0219, B:149:0x0249, B:157:0x025e, B:174:0x0290, B:183:0x02a8), top: B:288:0x005e }] */
    /* JADX WARN: Removed duplicated region for block: B:140:0x0232  */
    /* JADX WARN: Removed duplicated region for block: B:143:0x0237  */
    /* JADX WARN: Removed duplicated region for block: B:144:0x0239  */
    /* JADX WARN: Removed duplicated region for block: B:149:0x0249 A[Catch: all -> 0x0136, TRY_ENTER, TRY_LEAVE, TryCatch #2 {all -> 0x0136, blocks: (B:20:0x005e, B:22:0x0061, B:39:0x0098, B:40:0x009b, B:73:0x011e, B:44:0x00a5, B:61:0x00d8, B:62:0x00db, B:70:0x0106, B:66:0x00e4, B:68:0x00e9, B:51:0x00b9, B:54:0x00c3, B:57:0x00cd, B:29:0x0076, B:32:0x0081, B:35:0x008c, B:81:0x014d, B:83:0x0150, B:104:0x0199, B:105:0x019c, B:115:0x01cd, B:110:0x01a7, B:112:0x01ac, B:91:0x0169, B:94:0x0175, B:97:0x0180, B:100:0x018c, B:125:0x0207, B:132:0x0219, B:149:0x0249, B:157:0x025e, B:174:0x0290, B:183:0x02a8), top: B:288:0x005e }] */
    /* JADX WARN: Removed duplicated region for block: B:151:0x0250 A[Catch: all -> 0x04f5, TRY_ENTER, TryCatch #1 {all -> 0x04f5, blocks: (B:11:0x0034, B:13:0x0037, B:17:0x0047, B:78:0x013c, B:118:0x01e5, B:120:0x01f1, B:122:0x01f5, B:128:0x0210, B:146:0x023d, B:152:0x0252, B:171:0x0284, B:177:0x0299, B:186:0x02b3, B:176:0x0297, B:160:0x0269, B:151:0x0250, B:135:0x0224, B:127:0x020e), top: B:286:0x0034 }] */
    /* JADX WARN: Removed duplicated region for block: B:154:0x0258  */
    /* JADX WARN: Removed duplicated region for block: B:160:0x0269 A[Catch: all -> 0x04f5, TRY_ENTER, TRY_LEAVE, TryCatch #1 {all -> 0x04f5, blocks: (B:11:0x0034, B:13:0x0037, B:17:0x0047, B:78:0x013c, B:118:0x01e5, B:120:0x01f1, B:122:0x01f5, B:128:0x0210, B:146:0x023d, B:152:0x0252, B:171:0x0284, B:177:0x0299, B:186:0x02b3, B:176:0x0297, B:160:0x0269, B:151:0x0250, B:135:0x0224, B:127:0x020e), top: B:286:0x0034 }] */
    /* JADX WARN: Removed duplicated region for block: B:165:0x0278  */
    /* JADX WARN: Removed duplicated region for block: B:168:0x027d  */
    /* JADX WARN: Removed duplicated region for block: B:169:0x027f  */
    /* JADX WARN: Removed duplicated region for block: B:174:0x0290 A[Catch: all -> 0x0136, TRY_ENTER, TRY_LEAVE, TryCatch #2 {all -> 0x0136, blocks: (B:20:0x005e, B:22:0x0061, B:39:0x0098, B:40:0x009b, B:73:0x011e, B:44:0x00a5, B:61:0x00d8, B:62:0x00db, B:70:0x0106, B:66:0x00e4, B:68:0x00e9, B:51:0x00b9, B:54:0x00c3, B:57:0x00cd, B:29:0x0076, B:32:0x0081, B:35:0x008c, B:81:0x014d, B:83:0x0150, B:104:0x0199, B:105:0x019c, B:115:0x01cd, B:110:0x01a7, B:112:0x01ac, B:91:0x0169, B:94:0x0175, B:97:0x0180, B:100:0x018c, B:125:0x0207, B:132:0x0219, B:149:0x0249, B:157:0x025e, B:174:0x0290, B:183:0x02a8), top: B:288:0x005e }] */
    /* JADX WARN: Removed duplicated region for block: B:176:0x0297 A[Catch: all -> 0x04f5, TRY_ENTER, TryCatch #1 {all -> 0x04f5, blocks: (B:11:0x0034, B:13:0x0037, B:17:0x0047, B:78:0x013c, B:118:0x01e5, B:120:0x01f1, B:122:0x01f5, B:128:0x0210, B:146:0x023d, B:152:0x0252, B:171:0x0284, B:177:0x0299, B:186:0x02b3, B:176:0x0297, B:160:0x0269, B:151:0x0250, B:135:0x0224, B:127:0x020e), top: B:286:0x0034 }] */
    /* JADX WARN: Removed duplicated region for block: B:180:0x02a2  */
    /* JADX WARN: Removed duplicated region for block: B:186:0x02b3 A[Catch: all -> 0x04f5, TRY_ENTER, TRY_LEAVE, TryCatch #1 {all -> 0x04f5, blocks: (B:11:0x0034, B:13:0x0037, B:17:0x0047, B:78:0x013c, B:118:0x01e5, B:120:0x01f1, B:122:0x01f5, B:128:0x0210, B:146:0x023d, B:152:0x0252, B:171:0x0284, B:177:0x0299, B:186:0x02b3, B:176:0x0297, B:160:0x0269, B:151:0x0250, B:135:0x0224, B:127:0x020e), top: B:286:0x0034 }] */
    /* JADX WARN: Removed duplicated region for block: B:191:0x02c3  */
    /* JADX WARN: Removed duplicated region for block: B:194:0x02c8  */
    /* JADX WARN: Removed duplicated region for block: B:195:0x02ca  */
    /* JADX WARN: Removed duplicated region for block: B:199:0x02d2 A[Catch: all -> 0x0307, TRY_LEAVE, TryCatch #3 {all -> 0x0307, blocks: (B:197:0x02ce, B:199:0x02d2), top: B:289:0x02ce }] */
    /* JADX WARN: Removed duplicated region for block: B:205:0x02ff  */
    /* JADX WARN: Removed duplicated region for block: B:260:0x04fe A[Catch: all -> 0x050b, TRY_LEAVE, TryCatch #7 {all -> 0x050b, blocks: (B:260:0x04fe, B:141:0x0233, B:217:0x0346, B:166:0x0279, B:214:0x032e, B:221:0x0360, B:223:0x036b, B:225:0x036e, B:227:0x0375, B:230:0x0395, B:232:0x03a1, B:234:0x03a4, B:236:0x03ab, B:238:0x03c7, B:240:0x03d3, B:242:0x03d7, B:244:0x03db, B:245:0x03f7, B:247:0x0403, B:249:0x0407, B:251:0x040b, B:252:0x0420, B:254:0x042b, B:255:0x0432), top: B:297:0x0032 }] */
    /* JADX WARN: Removed duplicated region for block: B:265:0x050f A[Catch: all -> 0x0575, TryCatch #0 {all -> 0x0575, blocks: (B:262:0x0504, B:266:0x0522, B:268:0x0526, B:269:0x0532, B:271:0x0536, B:272:0x0542, B:274:0x0546, B:275:0x0553, B:265:0x050f), top: B:284:0x04fc }] */
    /* JADX WARN: Removed duplicated region for block: B:268:0x0526 A[Catch: all -> 0x0575, TryCatch #0 {all -> 0x0575, blocks: (B:262:0x0504, B:266:0x0522, B:268:0x0526, B:269:0x0532, B:271:0x0536, B:272:0x0542, B:274:0x0546, B:275:0x0553, B:265:0x050f), top: B:284:0x04fc }] */
    /* JADX WARN: Removed duplicated region for block: B:271:0x0536 A[Catch: all -> 0x0575, TryCatch #0 {all -> 0x0575, blocks: (B:262:0x0504, B:266:0x0522, B:268:0x0526, B:269:0x0532, B:271:0x0536, B:272:0x0542, B:274:0x0546, B:275:0x0553, B:265:0x050f), top: B:284:0x04fc }] */
    /* JADX WARN: Removed duplicated region for block: B:274:0x0546 A[Catch: all -> 0x0575, TryCatch #0 {all -> 0x0575, blocks: (B:262:0x0504, B:266:0x0522, B:268:0x0526, B:269:0x0532, B:271:0x0536, B:272:0x0542, B:274:0x0546, B:275:0x0553, B:265:0x050f), top: B:284:0x04fc }] */
    /* JADX WARN: Removed duplicated region for block: B:40:0x009b A[Catch: all -> 0x0136, TryCatch #2 {all -> 0x0136, blocks: (B:20:0x005e, B:22:0x0061, B:39:0x0098, B:40:0x009b, B:73:0x011e, B:44:0x00a5, B:61:0x00d8, B:62:0x00db, B:70:0x0106, B:66:0x00e4, B:68:0x00e9, B:51:0x00b9, B:54:0x00c3, B:57:0x00cd, B:29:0x0076, B:32:0x0081, B:35:0x008c, B:81:0x014d, B:83:0x0150, B:104:0x0199, B:105:0x019c, B:115:0x01cd, B:110:0x01a7, B:112:0x01ac, B:91:0x0169, B:94:0x0175, B:97:0x0180, B:100:0x018c, B:125:0x0207, B:132:0x0219, B:149:0x0249, B:157:0x025e, B:174:0x0290, B:183:0x02a8), top: B:288:0x005e }] */
    /* JADX WARN: Removed duplicated region for block: B:41:0x009f  */
    /* JADX WARN: Removed duplicated region for block: B:42:0x00a1  */
    /* JADX WARN: Removed duplicated region for block: B:43:0x00a3  */
    /* JADX WARN: Removed duplicated region for block: B:46:0x00ae  */
    /* JADX WARN: Removed duplicated region for block: B:57:0x00cd A[Catch: all -> 0x0136, TryCatch #2 {all -> 0x0136, blocks: (B:20:0x005e, B:22:0x0061, B:39:0x0098, B:40:0x009b, B:73:0x011e, B:44:0x00a5, B:61:0x00d8, B:62:0x00db, B:70:0x0106, B:66:0x00e4, B:68:0x00e9, B:51:0x00b9, B:54:0x00c3, B:57:0x00cd, B:29:0x0076, B:32:0x0081, B:35:0x008c, B:81:0x014d, B:83:0x0150, B:104:0x0199, B:105:0x019c, B:115:0x01cd, B:110:0x01a7, B:112:0x01ac, B:91:0x0169, B:94:0x0175, B:97:0x0180, B:100:0x018c, B:125:0x0207, B:132:0x0219, B:149:0x0249, B:157:0x025e, B:174:0x0290, B:183:0x02a8), top: B:288:0x005e }] */
    /* JADX WARN: Removed duplicated region for block: B:62:0x00db A[Catch: all -> 0x0136, TryCatch #2 {all -> 0x0136, blocks: (B:20:0x005e, B:22:0x0061, B:39:0x0098, B:40:0x009b, B:73:0x011e, B:44:0x00a5, B:61:0x00d8, B:62:0x00db, B:70:0x0106, B:66:0x00e4, B:68:0x00e9, B:51:0x00b9, B:54:0x00c3, B:57:0x00cd, B:29:0x0076, B:32:0x0081, B:35:0x008c, B:81:0x014d, B:83:0x0150, B:104:0x0199, B:105:0x019c, B:115:0x01cd, B:110:0x01a7, B:112:0x01ac, B:91:0x0169, B:94:0x0175, B:97:0x0180, B:100:0x018c, B:125:0x0207, B:132:0x0219, B:149:0x0249, B:157:0x025e, B:174:0x0290, B:183:0x02a8), top: B:288:0x005e }] */
    /* JADX WARN: Removed duplicated region for block: B:63:0x00de  */
    /* JADX WARN: Removed duplicated region for block: B:64:0x00e0  */
    /* JADX WARN: Removed duplicated region for block: B:65:0x00e2  */
    /* JADX WARN: Removed duplicated region for block: B:68:0x00e9 A[Catch: all -> 0x0136, TryCatch #2 {all -> 0x0136, blocks: (B:20:0x005e, B:22:0x0061, B:39:0x0098, B:40:0x009b, B:73:0x011e, B:44:0x00a5, B:61:0x00d8, B:62:0x00db, B:70:0x0106, B:66:0x00e4, B:68:0x00e9, B:51:0x00b9, B:54:0x00c3, B:57:0x00cd, B:29:0x0076, B:32:0x0081, B:35:0x008c, B:81:0x014d, B:83:0x0150, B:104:0x0199, B:105:0x019c, B:115:0x01cd, B:110:0x01a7, B:112:0x01ac, B:91:0x0169, B:94:0x0175, B:97:0x0180, B:100:0x018c, B:125:0x0207, B:132:0x0219, B:149:0x0249, B:157:0x025e, B:174:0x0290, B:183:0x02a8), top: B:288:0x005e }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void dump(java.io.FileDescriptor r25, java.io.PrintWriter r26, java.lang.String[] r27) {
        /*
            Method dump skipped, instructions count: 1464
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.usb.UsbService.dump(java.io.FileDescriptor, java.io.PrintWriter, java.lang.String[]):void");
    }

    private static String removeLastChar(String value) {
        return value.substring(0, value.length() - 1);
    }
}
